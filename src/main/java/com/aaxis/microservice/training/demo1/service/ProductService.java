package com.aaxis.microservice.training.demo1.service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.querydsl.QSort;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.aaxis.microservice.training.demo1.dao.CategoryDao;
import com.aaxis.microservice.training.demo1.dao.ProductDao;
import com.aaxis.microservice.training.demo1.domain.Category;
import com.aaxis.microservice.training.demo1.domain.Product;

@Service
public class ProductService {
    @Autowired
    private CategoryDao mCategoryDao;

    @Autowired
    private ProductDao mProductDao;
    
    @Autowired
    private RestTemplateBuilder mRestTemplateBuilder;

    @Autowired
    private Environment env;

    @Autowired
    private RestTemplate restTemplate;
    
    Logger logger = LoggerFactory.getLogger(this.getClass());

    private static  final int PRODUCT_BATCH_SIZE = 1000;

    public void initData() {
        List<Category> categories = mCategoryDao.findAll();

        if (categories == null) {
            return;
        }
        int maxProductCoundInCategory = Integer.parseInt(env.getProperty("maxProductCoundInCategory"));

        String checkProductExistBeforeAdding = env.getProperty("checkProductExistBeforeAdding");

        for (Category category : categories) {

            int randomProductSize = new Random().nextInt(maxProductCoundInCategory / 2) + maxProductCoundInCategory / 2;

            List<Product> productist = new ArrayList<>(PRODUCT_BATCH_SIZE);

            for (int i = 1; i <= randomProductSize; i++) {
                String productId = category.getId() + "_" + i;
                String productName = RandomStringUtils.randomAlphanumeric(32);
                if ("true".equalsIgnoreCase(checkProductExistBeforeAdding) && mProductDao.findById(productId).isPresent()) {
                    break;
                }
                Product product = new Product();
                product.setId(productId);
                product.setName(productName);
                product.setPriority(new Random().nextInt(100));
                Date date = randomDate("2010-01-01","2018-01-01");
                product.setCreatedDate(date);
//                product.setPrice(new BigDecimal(new Random().nextDouble() * 1000).setScale(2,   BigDecimal.ROUND_HALF_UP).doubleValue());
                product.setCategory(category);
//                mProductDao.save(product);
                productist.add(product);

                if(productist.size() % PRODUCT_BATCH_SIZE == 0){
                    mProductDao.saveAll(productist);
                    productist.clear();
                }
            }

            if(!productist.isEmpty()){
                mProductDao.saveAll(productist);
                productist.clear();
            }
        }
        restTemplate.getForObject("http://localhost:8081/price/initData", Map.class);
        restTemplate.getForObject("http://localhost:8082/inventory/initData", Map.class);
    }

    public List<Product> findProductsByCategoryId(String categoryId) {
        return mProductDao.findProductsByCategory_Id(categoryId);
    }
    
    public Page<Product> findProductsInPLP(String categoryId, int page, String sortName, String sortValue) {
        long startTime = System.currentTimeMillis();
        Specification<Product> spec = (Root<Product> pRoot, CriteriaQuery<?> pCriteriaQuery, CriteriaBuilder pCriteriaBuilder) -> {
	        Path<Category> name = pRoot.get("category");
	        Predicate p = pCriteriaBuilder.equal(name.as(Category.class), mCategoryDao.findById(categoryId).get());
	        return p;
        };
        Pageable pageable = null;
        if (sortName != null) {
            Sort sort = new Sort("ASC".equalsIgnoreCase(sortValue) ? QSort.Direction.ASC : QSort.Direction.DESC, sortName);
            pageable = new PageRequest(page-1, 20, sort);
        } else {
            pageable = new PageRequest(page-1, 20);
        }
        Page<String> idResult = mProductDao.findProductId(spec, pageable);
        List<String> idList = idResult.getContent();
        List<Product> products = mProductDao.findAllById(idList);
        PageImpl<Product> pageResult = new PageImpl<Product>(products, pageable, idResult.getTotalElements());
        long cost = System.currentTimeMillis()-startTime;
        logger.debug("FindProductsByCategoryId COST_TIME:" + cost);
        addPriceAndInventory(pageResult.getContent());
        return pageResult;
    }

    public Page<Product> searchProducts(int page, String productId, String name, String sortName, String sortValue) {
    	long startTime = System.currentTimeMillis();
    	Specification<Product> spec = (Root<Product> pRoot, CriteriaQuery<?> pCriteriaQuery, CriteriaBuilder pCriteriaBuilder) -> {
    		List<Predicate> predicates = new ArrayList<>();
    		if (StringUtils.isNotBlank(productId)) {
                Predicate predicate = pCriteriaBuilder.like(pRoot.get("id").as(String.class), "%"+productId+"%");
                predicates.add(predicate);
            }
    		if (StringUtils.isNotBlank(name)) {
                Predicate predicate = pCriteriaBuilder.like(pRoot.get("name").as(String.class), "%"+name+"%");
                predicates.add(predicate);
            }
    		Predicate[] predicateArr = new Predicate[predicates.size()];
            predicateArr = predicates.toArray(predicateArr);
	        return pCriteriaBuilder.or(predicateArr);
        };
        Pageable pageable = null;
        if (sortName != null) {
        	Sort sort = new Sort("ASC".equalsIgnoreCase(sortValue) ? QSort.Direction.ASC : QSort.Direction.DESC, sortName);
        	pageable = new PageRequest(page-1, 20, sort);
        } else {
        	pageable = new PageRequest(page-1, 20);
        }
        long cost = System.currentTimeMillis()-startTime;
        logger.debug("SearchProducts COST_TIME:" + cost);
        Page<Product> pageResult = mProductDao.findAll(spec, pageable);
        addPriceAndInventory(pageResult.getContent());
        return pageResult;
    }

    public void addPriceAndInventory(List<Product> products) {
    	products.forEach(this :: setPriceAndInventory);
    }
    
    public void setPriceAndInventory(Product product) {
    	product.setPrice(getProductPrice(product.getId()));
        product.setStock(getProductInventory(product.getId()));
	}
    
    @Bean
    public RestTemplate restTemplate() {
        return mRestTemplateBuilder.build();
    }

    public double getProductPrice(String pProductId) {
        Double price = (Double) ((Map) restTemplate.getForObject("http://localhost:8081/price/" + pProductId, Map.class)).get("price");
        return price;
    }

    public int getProductInventory(String pProductId) {
        Integer stock = (Integer) ((Map) restTemplate.getForObject("http://localhost:8082/inventory/" + pProductId, Map.class)).get("stock");
        return stock;
    }


    private static long random(long begin, long end) {
        long rtn = begin + (long) (Math.random() * (end - begin));
        if (rtn == begin || rtn == end) {
            return random(begin, end);
        }
        return rtn;
    }

    private static Date randomDate(String beginDate, String endDate) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
            Date start = format.parse(beginDate);
            Date end = format.parse(endDate);
            if (start.getTime() >= end.getTime()) {
                return null;
            }
            long date = random(start.getTime(), end.getTime());
            return new Date(date);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}

